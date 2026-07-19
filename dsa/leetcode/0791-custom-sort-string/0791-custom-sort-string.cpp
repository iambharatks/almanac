class Solution {
public:
    string customSortString(string order, string s) {
        string output(s);
        int n = size(s);
        vector<int> count(26,0);
        vector<int> idx(26,25);
        for(int i = 0 ; i < size(order); i++){
            idx[order[i]-'a'] = i;
        }
        for(int i = 0 ; i < size(s); i++){
            count[idx[s[i]-'a']] += 1; 
        }
        for(int i = 1 ; i < 26; i ++){
            count[i] += count[i-1];
        }
        for(int i = 0 ; i < n ; i++){
            output[count[idx[s[i]-'a']]-1]  = s[i];
            count[idx[s[i]-'a']]--;           
        }
        cout<<"here4";
        return output;
    }
};